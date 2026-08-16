package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TitleNormalizer
import com.streamflixreborn.streamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Base64

/**
 * 2026-05-05 : Helper cross-provider qui récupère les sources Coflix
 * (https://coflix.date, https://coflix.blog, etc.) et les renvoie sous forme
 * de [Video.Server] consommables par n'importe quel provider.
 *
 * Architecture Coflix :
 *   1. Search API : `GET /suggest.php?query={titre}` → JSON [{ID, title, url,
 *      post_type: movies|series, year}]
 *   2. Film page : `/film/{slug}/` → contient un iframe vers
 *      `lecteurvideo.com/embed.php?id={id}`
 *   3. Episode page : `/episode/{serie-slug}-{season}x{episode}/` → idem
 *   4. lecteurvideo.com embed → contient N boutons avec
 *      `onclick="showVideo('<base64>', '<sand>')"` où la base64 décode en URL
 *      d'un hoster (Lulustream / VOE / Vidoza / Darkibox / Veev / Goodstream
 *      / Minochinos / lecteur1.xtremestream.xyz mp4 / coflix.upn.one ...).
 *
 * Notre implem :
 *   - Fait domain rotation entre les miroirs connus
 *   - Score les candidats search par title+year fuzzy match
 *   - Fetch lecteurvideo, extrait toutes les onclick base64, décode, mappe
 *     chaque URL à un nom d'extracteur connu pour l'affichage
 *   - Filtre les hosters non supportés (xtremestream raw mp4 OK ; le reste
 *     est délégué à [Extractor] qui matche par URL)
 */
object CoflixSourceProvider {

    private const val TAG = "CoflixSourceProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Miroirs de repli si l'auto-découverte (CoflixMirrorDiscovery) échoue.
     *  On essaie dans l'ordre jusqu'à un 200.
     *  2026-07-17 : le domaine actif est désormais résolu dynamiquement via
     *  coflix.domains (redirect_url) — l'app suit les migrations sans release.
     *  2026-07-30 : coflix.domains redirige désormais vers **coflix.esq** (vérifié :
     *  vraie page Coflix). coflix.boston est MORT → retiré ; coflix.esq mis en tête. */
    private val MIRRORS = listOf(
        "https://coflix.esq",
        "https://coflix.cloud",
    )

    @Volatile private var lastWorkingMirror: String = MIRRORS.first()

    /** Rafraîchit lastWorkingMirror depuis le registre coflix.domains (cache 6h).
     *  Appelé au début de la résolution : quand Coflix migre, l'app suit sans
     *  release. Ne throw jamais (discover() renvoie au pire le défaut). */
    private suspend fun ensureActiveMirror() {
        try {
            val active = CoflixMirrorDiscovery.discover()
            if (active != lastWorkingMirror) {
                Log.d(TAG, "miroir actif (registre) : $lastWorkingMirror → $active")
                lastWorkingMirror = active
            }
        } catch (e: Exception) {
            Log.d(TAG, "ensureActiveMirror ignoré: ${e.message}")
        }
    }

    /** 2026-06-02 — Backoff après HTTP 429. Quand un mirror Coflix renvoie 429,
     *  on marque Coflix comme indispo pendant 30 min : tous les calls suivants
     *  retournent null instantanement, sans frapper le serveur. Ca evite le
     *  ban CF qui s'aggrave a force de hammer-and-retry. */
    private const val COOLDOWN_AFTER_429_MS = 30L * 60L * 1000L // 30 min
    @Volatile private var coflixCooldownUntilMs: Long = 0L

    /** Headers communs pour les calls Coflix. */
    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Referer" to "$lastWorkingMirror/",
    )

    private val httpClient by lazy { Extractor.sharedClient }

    private suspend fun httpGet(url: String, customReferer: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .apply {
                    headers().forEach { (k, v) ->
                        // 2026-06-02 : Referer overridable. lecteurvideo.com renvoie 403/520
                        // (CF bloque) si on envoie le Referer global (lastWorkingMirror) au lieu
                        // de l'URL de la page Coflix d'origine. Validé par curl : sans Referer
                        // 403, avec Referer = coflix.cymru/film/X → 200.
                        if (k == "Referer" && customReferer != null) header(k, customReferer)
                        else header(k, v)
                    }
                }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "GET $url → HTTP ${resp.code}")
                    if (resp.code == 429) {
                        // Coflix CF rate-limit. On gèle tous les appels pour 30 min.
                        coflixCooldownUntilMs = System.currentTimeMillis() + COOLDOWN_AFTER_429_MS
                        Log.w(TAG, "Coflix global cooldown 30 min apres HTTP 429")
                    }
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    /**
     * Public — récupère les sources Coflix pour un film.
     *
     * @param title Titre à matcher (ex: "Joker: Folie à deux", "Wednesday").
     * @param year Année de sortie (utilisée pour disambiguer remakes).
     * @return Liste de [Video.Server] (un par hoster Coflix), ou empty si
     *         aucun match ou erreur.
     */
    suspend fun getMovieSources(title: String, year: Int? = null, altTitle: String? = null): List<Video.Server> {
        // 2026-06-03 (user "Pourquoi ce film là Il est pas trouvé par Ce site
        //   Dans l'application" pour Aventures croisées 2026) : essai du titre
        //   principal puis fallback titre alternatif (souvent le titre original
        //   anglais quand TMDB FR n'a pas encore traduit, ou inversement). Le
        //   slug Coflix dépend du titre VF (ex : "aventures-croisees") alors
        //   que TMDB peut renvoyer "Swapped" pour les films récents → on ratait.
        val match = searchBest(title, year, type = "movies")
            ?: altTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?.let { searchBest(it, year, type = "movies") }
            // Dernier recours : on demande à TMDB la version FR du titre puis
            //   on retente. Couvre les films récents où Cloudstream/Movix ont
            //   le titre EN ("Swapped") mais Coflix indexe en FR ("aventures-
            //   croisees").
            ?: resolveFrenchTitleViaTmdb(title, year, isMovie = true)
                ?.takeIf { !it.equals(title, ignoreCase = true) }
                ?.let { Log.d(TAG, "Coflix fallback FR title TMDB: '$title' -> '$it'"); searchBest(it, year, type = "movies") }
            ?: return emptyList()
        return extractFromCoflixPage(match.url, label = "Coflix Boston")
    }

    /**
     * Public — récupère les sources Coflix pour un épisode de série.
     *
     * @param showTitle Titre de la série (ex: "Mercredi", "Wednesday").
     * @param year Année de sortie de la série (premier air-date).
     * @param seasonNumber Numéro de saison (1-based).
     * @param episodeNumber Numéro d'épisode (1-based).
     */
    suspend fun getEpisodeSources(
        showTitle: String,
        year: Int? = null,
        seasonNumber: Int,
        episodeNumber: Int,
        altShowTitle: String? = null,
    ): List<Video.Server> {
        // 2026-06-03 : même fallback titre alternatif que pour les films.
        val match = searchBest(showTitle, year, type = "series")
            ?: altShowTitle?.takeIf { it.isNotBlank() && !it.equals(showTitle, ignoreCase = true) }
                ?.let { searchBest(it, year, type = "series") }
            ?: resolveFrenchTitleViaTmdb(showTitle, year, isMovie = false)
                ?.takeIf { !it.equals(showTitle, ignoreCase = true) }
                ?.let { Log.d(TAG, "Coflix fallback FR series TMDB: '$showTitle' -> '$it'"); searchBest(it, year, type = "series") }
            ?: return emptyList()
        // L'URL du serie s'appelle `/serie/{slug}/`. On en extrait le slug pour
        // construire l'URL épisode `/episode/{slug}-{season}x{episode}/`.
        val serieSlug = match.url
            .substringAfter("/serie/")
            .substringBefore("/")
            .trim()
        if (serieSlug.isBlank()) return emptyList()

        // 2026-07-14 FIX (House of the Dragon) : WordPress ajoute parfois un suffixe de
        //   désambiguïsation au slug de la SÉRIE (ex « house-of-the-dragon-p2 ») qui N'EST PAS
        //   présent sur les slugs d'ÉPISODE (« house-of-the-dragon-3x4 »). Résultat : l'URL
        //   « …-p2-3x4 » renvoie 404 → 0 serveur. On essaie donc le slug complet PUIS le slug
        //   débarrassé d'un suffixe « -pN » / « -N » final.
        val slugCandidates = buildList {
            add(serieSlug)
            val stripped = serieSlug.replace(Regex("-p?\\d+$"), "")
            if (stripped.isNotBlank() && stripped != serieSlug) add(stripped)
        }
        // ── 2026-08-16 : LIRE la liste d'épisodes AVANT de la deviner ────────
        //   Coflix a DEUX conventions de nommage d'épisode qui coexistent :
        //     • courte   : /episode/<slug>-1x3/                    ← devinable
        //     • longue   : /episode/<slug>-1x01-54912-6a7a48a4a7/  ← INDEVINABLE
        //       (numéro sur 2 chiffres + id interne + hash)
        //   Sur la 2ᵉ, l'URL fabriquée renvoyait 404 → `cfServers` jamais lu →
        //   0 serveur, alors que le match de titre était parfait (score=100).
        //   Constaté sur « New York Unité Spéciale » S1E1 (log : GET …-1x1/ → 404),
        //   et ce sont justement les fiches en nommage LONG qui servent le nouveau
        //   format à hébergeurs directs — les deux moitiés de la même panne.
        //   On lit donc les liens réels sur la page de la série et on retient celui
        //   qui porte le bon SxE. La devinette reste en repli (boucle suivante),
        //   donc les fiches à nommage court gardent exactement le comportement
        //   d'avant — pas de régression sur ce qui marche déjà.
        run {
            val serieHtml = httpGet(match.url) ?: return@run
            val liens = Regex("""/episode/[a-z0-9\-]+/?""")
                .findAll(serieHtml)
                .map { it.value }
                .distinct()
                .toList()
            if (liens.isEmpty()) return@run
            // `1x01` comme `1x1` : on tolère le zéro de tête, et on exige une
            // frontière derrière (fin, tiret ou slash) pour ne pas confondre
            // l'épisode 1 avec le 10, le 11, etc.
            val motif = Regex("""-${seasonNumber}x0*${episodeNumber}(?:[-/]|$)""")
            val cible = liens.firstOrNull { motif.containsMatchIn(it) } ?: return@run
            val episodeUrl = "${urlBase(match.url)}${if (cible.startsWith("/")) cible else "/$cible"}"
            val servers = extractFromCoflixPage(episodeUrl, label = "Coflix Boston")
            if (servers.isNotEmpty()) {
                Log.i(TAG, "getEpisodeSources '$showTitle' S${seasonNumber}E$episodeNumber → lien LU '$cible' → ${servers.size} serveurs")
                return servers
            }
        }

        for (slug in slugCandidates) {
            val episodeUrl = "${urlBase(match.url)}/episode/$slug-${seasonNumber}x${episodeNumber}/"
            val servers = extractFromCoflixPage(episodeUrl, label = "Coflix Boston")
            if (servers.isNotEmpty()) {
                Log.i(TAG, "getEpisodeSources '$showTitle' S${seasonNumber}E$episodeNumber → slug='$slug' → ${servers.size} serveurs")
                return servers
            }
        }
        Log.i(TAG, "getEpisodeSources '$showTitle' S${seasonNumber}E$episodeNumber → 0 (slugs testés: $slugCandidates)")
        return emptyList()
    }

    private fun urlBase(url: String): String {
        return url.substringBefore("/", missingDelimiterValue = url)
            .let { if (it.startsWith("http")) it.substringBefore("/", url) else url }
            .let { Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: lastWorkingMirror }
    }

    private data class CoflixMatch(val title: String, val url: String, val year: String?)

    /** Recherche un titre et retourne le meilleur match (par titre+année). */
    private suspend fun searchBest(rawTitle: String, year: Int?, type: String): CoflixMatch? {
        val cleanTitle = TitleNormalizer.cleanForTmdbSearch(rawTitle).ifBlank { rawTitle }
        if (cleanTitle.isBlank()) return null
        if (System.currentTimeMillis() < coflixCooldownUntilMs) {
            val remainSec = (coflixCooldownUntilMs - System.currentTimeMillis()) / 1000
            Log.d(TAG, "Coflix en cooldown 429 (${remainSec}s restants) — skip")
            return null
        }
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        // 2026-07-17 : résout le domaine Coflix actif via coflix.domains (cache 6h)
        //   AVANT de construire la liste — l'app suit les migrations sans release.
        ensureActiveMirror()
        // WordPress : utilise WP REST API pour la recherche.
        //   L'ancien suggest.php n'existe plus (HTTP 500).
        val mirrors = buildList {
            if (lastWorkingMirror !in this) add(lastWorkingMirror)
            MIRRORS.forEach { if (it !in this) add(it) }
        }
        // 1) WP REST API search (coflix.boston)
        for (mirror in mirrors) {
            val url = "$mirror/wp-json/wp/v2/search?search=$encoded&type=post&per_page=20"
            val body = httpGet(url) ?: continue
            lastWorkingMirror = mirror
            pickBestWpApi(body, cleanTitle, year, type)?.let { return it }
        }
        // 2) Fallback slug-direct : on construit l'URL à partir du titre slugifié.
        //    Validation par la présence de cfServers ou lecteurvideo dans la page.
        val slug = slugify(cleanTitle)
        if (slug.isNotBlank()) {
            val pathSegment = if (type == "movies") "film" else "serie"
            for (mirror in mirrors) {
                val directUrl = "$mirror/$pathSegment/$slug/"
                val body = httpGet(directUrl) ?: continue
                if (body.contains("cfServers") || body.contains("lecteurvideo")) {
                    lastWorkingMirror = mirror
                    Log.d(TAG, "Coflix slug-direct fallback: '$cleanTitle' → $directUrl")
                    return CoflixMatch(cleanTitle, directUrl, year?.toString())
                }
            }
            Log.d(TAG, "Coflix slug-direct fallback: aucun mirror n'a /$pathSegment/$slug/")
        }
        return null
    }

    /** 2026-06-03 : Résout le titre FR d'un film/série via TMDB.
     *  Sert de pont quand le caller passe le titre EN ("Swapped") alors que
     *  Coflix indexe en FR ("aventures-croisees"). On demande TMDB en fr-FR
     *  pour récupérer la traduction officielle, puis on retente Coflix avec.
     *  Retourne null si TMDB ne connaît pas le film ou si la version FR
     *  n'apporte rien (= égale à l'input). */
    private suspend fun resolveFrenchTitleViaTmdb(rawTitle: String, year: Int?, isMovie: Boolean): String? = try {
        if (isMovie) {
            TmdbUtils.getMovie(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        } else {
            TmdbUtils.getTvShow(rawTitle, year, language = "fr-FR")?.title?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.d(TAG, "TMDB FR resolve failed for '$rawTitle' ($year): ${e.message}")
        null
    }

    /** Slugifie un titre pour construire une URL Coflix.
     *  "Super Charlie" → "super-charlie", "L'Été" → "l-ete". */
    private fun slugify(title: String): String =
        title.lowercase()
            .replace("é", "e").replace("è", "e").replace("ê", "e").replace("ë", "e")
            .replace("à", "a").replace("â", "a").replace("ä", "a")
            .replace("ô", "o").replace("ö", "o")
            .replace("ù", "u").replace("û", "u").replace("ü", "u")
            .replace("ç", "c").replace("î", "i").replace("ï", "i")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /**
     * 2026-07-12 : Parse le JSON WP REST API search et pioche le meilleur match.
     * Résultats = [{id, title, url, type, subtype}] où subtype = "movies"|"series"|"animes"|"doramas".
     */
    private fun pickBestWpApi(json: String, query: String, year: Int?, type: String): CoflixMatch? {
        return try {
            val arr = JSONArray(json)
            val candidates = mutableListOf<Pair<CoflixMatch, Int>>()
            // WP REST API subtype mapping : "movies" pour films, "series" pour séries
            val wantedSubtype = if (type == "movies") "movies" else "series"
            val queryNorm = TitleNormalizer.stripUnicodeArtifacts(query).lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val queryWords = queryNorm.split(" ").filter { it.length > 1 }.toSet()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val subtype = obj.optString("subtype")
                // Accepter "animes" comme série aussi
                if (subtype != wantedSubtype && !(wantedSubtype == "series" && subtype == "animes")) continue
                val title = obj.optString("title")
                val rawUrl = obj.optString("url")
                if (title.isBlank() || rawUrl.isBlank()) continue
                // WP REST API ne renvoie pas l'année — on extrait du slug si possible
                val slugYear = Regex("""(\d{4})""").find(
                    rawUrl.substringAfterLast("/").substringBeforeLast("/")
                )?.groupValues?.get(1)
                val titleNorm = TitleNormalizer.stripUnicodeArtifacts(title).lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
                val titleWords = titleNorm.split(" ").filter { it.length > 1 }.toSet()
                val lenDiffPct = if (kotlin.math.max(titleNorm.length, queryNorm.length) > 0)
                    kotlin.math.abs(titleNorm.length - queryNorm.length).toDouble() /
                        kotlin.math.max(titleNorm.length, queryNorm.length)
                else 0.0
                var score = when {
                    titleNorm == queryNorm -> 100
                    queryWords.isNotEmpty() && titleWords.containsAll(queryWords) && lenDiffPct <= 0.30 -> 90
                    titleWords.isNotEmpty() && queryWords.containsAll(titleWords) && lenDiffPct <= 0.30 -> 80
                    else -> 0
                }
                if (year != null && slugYear == year.toString()) score += 30
                else if (year != null && slugYear != null && slugYear.toIntOrNull() != null &&
                    kotlin.math.abs(slugYear.toInt() - year) > 2) score -= 50
                if (score >= 80) candidates.add(CoflixMatch(title, rawUrl, slugYear) to score)
            }
            val best = candidates.maxByOrNull { it.second }
            if (best == null) {
                Log.d(TAG, "Coflix WP '$query' (year=$year type=$type) : pas de match")
            } else {
                Log.d(TAG, "Coflix WP '$query' → '${best.first.title}' score=${best.second}")
            }
            best?.first
        } catch (e: Exception) {
            Log.d(TAG, "pickBestWpApi failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch une page Coflix (film ou épisode), extrait les serveurs vidéo.
     *
     * 2026-07-12 : supporte DEUX formats :
     *   (a) Nouveau (coflix.boston WordPress) : inline JS `var cfServers = [{nombre, embed_url, idioma}]`
     *       + `var cfPlayerToken = "..."`. L'iframe lecteurvideo = embed_url + "&t=" + token.
     *   (b) Ancien (legacy) : iframe lecteurvideo directement dans le HTML de la page.
     *
     * Dans les deux cas, l'embed lecteurvideo contient des onclick showVideo base64.
     */
    private suspend fun extractFromCoflixPage(coflixPageUrl: String, label: String): List<Video.Server> {
        val pageHtml = httpGet(coflixPageUrl) ?: return emptyList()
        // ── (a) Nouveau format WordPress : cfServers inline ──────────────────
        val embedUrls = mutableListOf<String>()
        val cfServersMatch = Regex("""var\s+cfServers\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
            .find(pageHtml)
        val cfTokenMatch = Regex("""var\s+cfPlayerToken\s*=\s*"([^"]+)"""").find(pageHtml)
        if (cfServersMatch != null) {
            val token = cfTokenMatch?.groupValues?.get(1) ?: ""
            try {
                val arr = JSONArray(cfServersMatch.groupValues[1])
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    var embedUrl = obj.optString("embed_url")
                    if (embedUrl.isBlank()) continue
                    // Ajouter le token comme paramètre &t=
                    // ⚠ 2026-08-16 : UNIQUEMENT sur la page intermédiaire de Coflix.
                    //   `cfPlayerToken` est un JWT propre à Coflix, destiné à
                    //   lecteurvideo.com. Depuis que `cfServers` pointe directement sur
                    //   les hébergeurs tiers, on le collait à des URL qui n'en veulent
                    //   pas (frembed.casa, vidzy.cc…) → paramètre parasite.
                    //   Constaté en direct : `frembed.casa/api/serie.php?id=…&sa=1&epi=1`
                    //   s'ouvre normalement dans le navigateur (lecteur « VF · Uqload »),
                    //   alors qu'ONYX l'appelait avec `&t=<JWT Coflix>` et échouait.
                    val estIntermediaireUrl = embedUrl.contains("lecteurvideo", ignoreCase = true) ||
                        embedUrl.contains("coflix", ignoreCase = true)
                    if (token.isNotBlank() && estIntermediaireUrl) {
                        embedUrl += (if (embedUrl.contains("?")) "&" else "?") + "t=$token"
                    }
                    embedUrls.add(embedUrl)
                }
                Log.d(TAG, "cfServers inline: ${embedUrls.size} embeds, token=${token.length} chars")
            } catch (e: Exception) {
                Log.d(TAG, "cfServers parse failed: ${e.message}")
            }
        }
        // ── (b) Fallback ancien format : iframe dans le HTML ─────────────────
        if (embedUrls.isEmpty()) {
            val iframeUrl = Regex("""<iframe[^>]*src="([^"]+lecteurvideo[^"]+)"""").find(pageHtml)
                ?.groupValues?.get(1)
                ?: Regex("""<iframe[^>]*src="(https?://[^"]+)"""").find(pageHtml)?.groupValues?.get(1)
            if (iframeUrl != null) embedUrls.add(iframeUrl)
        }
        if (embedUrls.isEmpty()) return emptyList()
        // ── Fetch chaque embed lecteurvideo et extraire les showVideo base64 ──
        val results = mutableListOf<Video.Server>()
        for (embedUrl in embedUrls) {
            // ── 2026-08-16 : cfServers pointe DÉSORMAIS DIRECTEMENT sur l'hébergeur ──
            //   Constaté en direct sur coflix.esq (Black Torch 1x07) : les `embed_url`
            //   valent voembed.net/embed-<id>.html, gn1r5n.org/e/<id>, voe.sx/e/<id>,
            //   streamtape.com/e/<id> — il n'y a PLUS de page intermédiaire
            //   `lecteurvideo`, donc PLUS de `onclick="showVideo('<base64>')"` à
            //   décoder. La boucle ci-dessous ne trouvait rien → Coflix rendait
            //   ZÉRO serveur sur ce gabarit, en silence.
            //   Correctif ADDITIF (on ne touche pas au chemin legacy, cf. règle
            //   « ne pas restructurer un getServers qui marche ») : si l'URL est
            //   déjà celle d'un hébergeur, on l'émet telle quelle et on s'épargne
            //   au passage un aller-retour réseau par serveur.
            val estIntermediaire = embedUrl.contains("lecteurvideo", ignoreCase = true) ||
                embedUrl.contains("coflix", ignoreCase = true)
            if (!estIntermediaire) {
                if (embedUrl.contains("streamhg", ignoreCase = true)) continue
                // 2026-08-16 (user : « il ne marche pas et on est déjà couvert ») :
                //   Coflix annonce parfois le POINT D'ENTRÉE de l'API Frembed
                //   (`frembed.<tld>/api/serie.php?id=…`), pas un lien d'hébergeur.
                //   ⚠ Piège de nommage : la classe `FrembedExtractor` existe mais
                //   n'est PAS enregistrée dans `Extractor.extractors` et son
                //   `extract()` lève volontairement — ce n'est pas un extracteur,
                //   c'est un DÉPLIEUR appelé à la main par FrembedProvider pour
                //   obtenir les vrais hébergeurs. Cette URL ne peut donc jamais être
                //   extraite : log « HOTE NON COUVERT: frembed.casa ».
                //   Et le contenu est DÉJÀ fourni, déplié, par le provider Frembed
                //   (même épisode → link1/2/3 + source VIP). La déduplication ne
                //   peut pas les fusionner : elle compare les URL, or l'une est
                //   l'adresse d'arrivée et l'autre celle du guichet.
                //   → on n'émet pas ce serveur mort-né. Même principe que le skip
                //   Streamhg juste au-dessus, et que « pas de serveur plutôt qu'un
                //   mauvais ».
                if (Regex("""frembed\.[a-z]+/api/""", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl)) {
                    Log.d(TAG, "Coflix : entrée API Frembed ignorée (déjà couverte par le provider Frembed)")
                    continue
                }
                results.add(Video.Server(
                    id = "coflix_${results.size}",
                    name = "$label · ${guessHosterName(embedUrl)}",
                    src = embedUrl,
                ))
                continue
            }

            val embedHtml = httpGet(embedUrl, customReferer = coflixPageUrl) ?: continue
            val regex = Regex("""onclick="showVideo\('([^']+)',\s*'[^']*'\)"""")
            for (m in regex.findAll(embedHtml)) {
                val b64 = m.groupValues[1]
                val decoded = runCatching { String(Base64.getDecoder().decode(b64)) }.getOrNull() ?: continue
                if (decoded.isBlank() || !decoded.startsWith("http")) continue
                // 2026-07-13 : Streamhg = EarnVids derrière Cloudflare Turnstile qui ne
                //   s'auto-résout QUE dans une WebView rendue à l'écran (échec garanti en
                //   headless depuis l'IP courante). Inutile de le lister NI de tenter
                //   l'extraction → on le saute (le film a déjà ~13 autres serveurs).
                if (decoded.contains("streamhg", ignoreCase = true)) continue
                val hosterName = guessHosterName(decoded)
                results.add(Video.Server(
                    id = "coflix_${results.size}",
                    name = "$label · $hosterName",
                    src = decoded,
                ))
            }
        }
        Log.d(TAG, "extractFromCoflixPage($coflixPageUrl) → ${results.size} sources")
        return results
    }

    /** Devine le nom du hoster à partir de l'URL pour l'affichage utilisateur. */
    private fun guessHosterName(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").lowercase()
        return when {
            host.contains("lulustream") -> "Lulustream"
            host.contains("voe.") || host.contains("voe-") -> "VOE"
            host.contains("vidoza") -> "Vidoza"
            host.contains("darkibox") -> "Darkibox"
            host.contains("veev.") -> "Veev"
            host.contains("goodstream") -> "Goodstream"
            host.contains("minochinos") -> "Minochinos"
            host.contains("filemoon") -> "Filemoon"
            host.contains("doodstream") || host.contains("dood.") -> "Doodstream"
            host.contains("uqload") -> "Uqload"
            host.contains("streamtape") -> "Streamtape"
            // 2026-08-16 : libellés maison de Coflix → vrai nom d'hébergeur, sinon
            //   le picker affichait « Voembed » / « Gn1r5n ». voembed = front Vidmoly,
            //   gn1r5n = frontend Byse (Filemoon). Cf. VidMoLyExtractor/FilemoonExtractor.
            host.contains("voembed") -> "VidMoLy"
            host.contains("gn1r5n") -> "Filemoon"
            host.contains("megaup") -> "MegaUp"
            host.contains("xtremestream") -> "MP4 Direct"
            host.contains("upn.one") -> "Coflix Upn"
            else -> host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        }
    }
}
