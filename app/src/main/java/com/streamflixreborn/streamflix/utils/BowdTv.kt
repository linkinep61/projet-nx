package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 2026-09-10 — Dossier TV Hub « Bowd » (bowdtv.com).
 *
 *  Ce qu'on a mesuré avant d'écrire une ligne (ne pas re-tester à l'aveugle) :
 *   • Le CATALOGUE est ouvert avec un simple jeton anonyme :
 *       POST auth.bowdtv.com/api/auth/anonymous/token   body {}   → { token: <JWT> }
 *       GET  api.bowdtv.com/api/v1/channels?country=France&cursor=N&limit=50&all=1
 *       en-têtes : Authorization: Bearer <jwt>, X-Bod-Device: <uuid>, x-bod-platform
 *     → 689 chaînes FR. Aucun captcha, aucun compte.
 *   • La LECTURE est fermée : /api/v1/channels/<id>/stream répond 403 ACCOUNT_REQUIRED
 *     avec le jeton anonyme, et l'URL HLS qu'il renverrait est signée par session
 *     (token opaque sur domaine jetable) — donc INUTILISABLE en dur dans un M3U.
 *   • Le flux « invité » de leur app (/api/auth/guest/challenge) est mort côté serveur :
 *     l'APK officielle elle-même se prend un 404 en boucle (vu au logcat sur l'Oppo).
 *
 *  D'où le choix (user : « il faut faire ça directement par le navigateur ») : on liste
 *  en API, et on LIT dans une WebView sur leur propre page player. L'utilisateur se
 *  connecte une fois chez eux, dans leur formulaire ; on ne manipule aucun identifiant.
 *  Le jour où /api/auth/guest/challenge répondra, on pourra basculer sur l'API.
 *
 *  Le champ `id` sert à tout : logo (bod-logos/<id>.webp) et page player (/player/<id>).
 *  L'API ne renvoie NI catégorie NI logo — les catégories sont déduites du nom, sur la
 *  taxonomie déjà utilisée par le TV Hub pour que les favoris se rangent pareil.
 */
object BowdTv {

    private const val TAG = "BowdTv"

    const val SITE = "https://bowdtv.com"
    private const val API = "https://api.bowdtv.com"
    private const val AUTH = "https://auth.bowdtv.com"
    private const val LOGOS = "https://logos.bowdtv.com/bod-logos"

    const val PREFIX = "livehub::bowd::"
    const val FOLDER_KEY = "bowd"
    const val LOGO = "$SITE/brand/icon-192.png"

    private const val PAYS = "France"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Chaine(val id: String, val nom: String, val statut: String) {
        val logo: String get() = "$LOGOS/$id.webp"
        val pagePlayer: String get() = "$SITE/player/$id"
    }

    private val JSON by lazy { "application/json".toMediaType() }

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            // 40 s : leur /vod/playable fait 594 Ko et dépassait régulièrement 20 s
            //   (« index VOD KO : timeout » mesuré). Le cache disque fait que ce délai
            //   ne sert qu'au tout premier chargement.
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Identifiant d'appareil attendu par l'API (X-Bod-Device). Un par process suffit :
     *  il ne sert qu'à lire le catalogue, aucune donnée n'y est rattachée. */
    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    @Volatile private var jeton: String? = null
    @Volatile private var jetonExpireMs: Long = 0L
    @Volatile private var cache: List<Chaine> = emptyList()
    @Volatile private var cacheTs: Long = 0L

    fun estChaine(id: String) = id.startsWith(PREFIX)
    private fun idChaine(id: String) = id.removePrefix(PREFIX).substringBefore("::")
    fun chainesSiDejaChargees(): List<Chaine> = cache
    fun listePerimee(): Boolean = cache.isEmpty() || System.currentTimeMillis() - cacheTs > TTL_MS

    // ─────────────────────────────── Jeton anonyme

    private fun jetonAnonyme(): String? {
        val maintenant = System.currentTimeMillis()
        jeton?.let { if (maintenant < jetonExpireMs) return it }
        return try {
            val req = okhttp3.Request.Builder()
                .url("$AUTH/api/auth/anonymous/token")
                .header("content-type", "application/json")
                .header("User-Agent", UA)
                .post("{}".toRequestBody(JSON))
                .build()
            val corps = client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "jeton anonyme refusé : ${r.code}"); return null }
                r.body?.string().orEmpty()
            }
            val t = JSONObject(corps).optString("token").takeIf { it.isNotBlank() } ?: return null
            jeton = t
            // Pas de parsing JWT : on reprend un jeton frais toutes les 4 min, c'est gratuit.
            jetonExpireMs = maintenant + 4 * 60 * 1000L
            t
        } catch (t: Throwable) {
            Log.w(TAG, "jeton anonyme KO : ${t.message}")
            null
        }
    }

    // ─────────────────────────────── Catalogue

    suspend fun chaines(forcer: Boolean = false): List<Chaine> = withContext(Dispatchers.IO) {
        val maintenant = System.currentTimeMillis()
        if (!forcer && cache.isNotEmpty() && maintenant - cacheTs < TTL_MS) return@withContext cache
        val jwt = jetonAnonyme() ?: return@withContext cache

        val sortie = ArrayList<Chaine>(700)
        var curseur: Int? = 0
        var pages = 0
        while (curseur != null && pages < 30) {
            val url = "$API/api/v1/channels?country=$PAYS&cursor=$curseur&limit=50&all=1"
            val corps = try {
                val req = okhttp3.Request.Builder().url(url)
                    .header("Authorization", "Bearer $jwt")
                    .header("X-Bod-Device", deviceId)
                    .header("x-bod-platform", "android")
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { Log.w(TAG, "catalogue HTTP ${r.code} (curseur $curseur)"); null }
                    else r.body?.string()
                }
            } catch (t: Throwable) { Log.w(TAG, "catalogue KO : ${t.message}"); null } ?: break

            val o = try { JSONObject(corps) } catch (_: Throwable) { break }
            val arr = o.optJSONArray("channels") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id").takeIf { it.isNotBlank() } ?: continue
                val nom = c.optString("name").takeIf { it.isNotBlank() } ?: id
                sortie.add(Chaine(id, nom, c.optString("status", "")))
            }
            curseur = if (o.isNull("nextCursor")) null else o.optInt("nextCursor").takeIf { it > 0 }
            pages++
        }

        if (sortie.isNotEmpty()) {
            cache = sortie
            cacheTs = maintenant
            Log.d(TAG, "catalogue Bowd : ${sortie.size} chaînes en $pages pages")
        }
        cache
    }

    // ─────────────────────────────── Catégories (déduites du nom)

    private val REGLES: List<Pair<String, Regex>> = listOf(
        "Sport" to Regex("\\b(sport|bein|eurosport|espn|foot|rugby|tennis|golf|ligue|dazn|canal\\+ ?sport|automoto|equipe)\\b", RegexOption.IGNORE_CASE),
        "Info" to Regex("\\b(info|news|bfm|cnews|lci|franceinfo|i24|euronews|cnn|africa ?news)\\b", RegexOption.IGNORE_CASE),
        "Cinéma" to Regex("\\b(cin[eé]|film|movie|paramount|action|thriller|western|ocs|tcm)\\b", RegexOption.IGNORE_CASE),
        "Enfants" to Regex("\\b(kids|junior|enfant|gulli|piwi|tiji|cartoon|nickelodeon|disney|boomerang|okoo|teen)\\b", RegexOption.IGNORE_CASE),
        "Documentaire" to Regex("\\b(docu|discovery|nat ?geo|histoire|history|science|planete|ushuaia|animaux|toute l'histoire)\\b", RegexOption.IGNORE_CASE),
        "Musique" to Regex("\\b(music|musique|mtv|trace|mcm|melody|clip|nrj ?hits|rfm|virgin|m6 ?music)\\b", RegexOption.IGNORE_CASE),
        "Divertissement" to Regex("\\b(novelas|s[eé]rie|comedy|com[eé]die|tel[eé]r[eé]alit[eé]|e! ?entertainment|entertainment|lifestyle|voyage|cuisine)\\b", RegexOption.IGNORE_CASE),
    )

    private fun categorieDe(nom: String): String {
        for ((libelle, rx) in REGLES) if (rx.containsMatchIn(nom)) return libelle
        return "Généraliste"
    }

    fun tuile(c: Chaine): TvShow = TvShow(id = "$PREFIX${c.id}", title = c.nom).apply {
        providerName = "TV Hub"
        poster = c.logo
        banner = c.logo
    }

    /** Une catégorie par thème, chaînes triées par nom. Les chaînes marquées hors service
     *  par l'API (`status` != ok) sont reléguées en fin de liste plutôt que masquées :
     *  leur statut bouge souvent et une chaîne cachée est plus déroutante qu'une qui rate. */
    fun categories(chaines: List<Chaine>): List<Category> {
        if (chaines.isEmpty()) return emptyList()
        val ordre = listOf("Généraliste", "Info", "Sport", "Cinéma", "Divertissement", "Documentaire", "Musique", "Enfants")
        val parCat = chaines.groupBy { categorieDe(it.nom) }
        return ordre.mapNotNull { cat ->
            val liste = parCat[cat]?.sortedWith(
                compareBy({ if (it.statut.equals("ok", true)) 0 else 1 }, { it.nom.lowercase() })
            ) ?: return@mapNotNull null
            Category(name = "Bowd - $cat", list = liste.map { tuile(it) })
        }
    }

    suspend fun chaine(id: String): Chaine? {
        val cid = idChaine(id)
        cache.firstOrNull { it.id == cid }?.let { return it }
        return chaines().firstOrNull { it.id == cid }
    }

    /** Fiche synthétique « En Direct » (favori rouvert, mini-lecteur…). */
    suspend fun fiche(id: String): TvShow {
        val c = chaine(id)
        val logo = c?.logo ?: LOGO
        return TvShow(id = id, title = c?.nom ?: "Bowd").apply {
            providerName = "TV Hub"
            poster = logo
            banner = logo
            overview = "Chaîne Bowd — lecture dans le lecteur web intégré."
        }
    }

    /**
     * 2026-09-10 (user : « on n'utilise pas WebView, on se connecte puis après on prend
     * les choses sur le site ») : si l'utilisateur est connecté, on demande le VRAI flux
     * à leur API et on le joue en natif dans ExoPlayer — mini-lecteur, favoris et
     * télécommande retrouvent leur comportement normal. Sans session, on retombe sur
     * leur page player en WebView, qui invite à se connecter.
     */
    suspend fun serveurs(id: String): List<Video.Server> {
        val c = chaine(id) ?: return emptyList()
        val flux = fluxHls(c.id)
        if (flux.isNotEmpty()) {
            return flux.mapIndexed { i, (libelle, url) ->
                Video.Server(id = "$PREFIX${c.id}::hls$i", name = "Bowd · $libelle", src = url)
            }
        }
        // 2026-09-10 (user : « quand je clique sur un live ça m'amène sur leur page web
        //   alors qu'on avait changé ça ») : le repli WebView ne vaut QUE pour un
        //   utilisateur non connecté, à qui il sert d'invitation à se connecter. Connecté,
        //   un flux qui échoue — leur API rend des 503 par intermittence, constaté — ne doit
        //   surtout pas le faire sortir de l'application : la chaîne n'a simplement aucun
        //   serveur Bowd pour l'instant, comme n'importe quelle source momentanément morte.
        val ctx = runCatching {
            com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
        }.getOrNull()
        if (ctx != null && BowdAuth.estConnecte(ctx)) {
            Log.d(TAG, "flux ${c.id} indisponible → aucun serveur (pas de repli web, session active)")
            return emptyList()
        }
        return listOf(
            Video.Server(id = "$PREFIX${c.id}::web", name = "Bowd · se connecter", src = c.pagePlayer)
        )
    }

    /** true si le serveur doit passer par la WebView plutôt que par ExoPlayer. */
    fun estServeurWeb(serverId: String) = serverId.endsWith("::web")

    /**
     * URL HLS réelle d'une chaîne, via la session de l'utilisateur. Null s'il n'est pas
     * connecté (403 ACCOUNT_REQUIRED) ou si la réponse ne contient pas de m3u8.
     *
     * La forme exacte de la réponse n'a pas pu être observée à l'écriture (l'endpoint est
     * fermé en anonyme), d'où la lecture tolérante : on cherche la première URL .m3u8 où
     * qu'elle soit dans le JSON. La réponse brute est journalisée une fois pour qu'on
     * puisse resserrer ça — et bâtir le VOD — sur du réel.
     */
    /**
     * Sources HLS d'une chaîne, via la session de l'utilisateur.
     *
     * Forme réelle observée (logcat, compte connecté) :
     *   {"streams":[{"quality":"auto",
     *                "url":"https://api.bowdtv.com/p/c/<id>/v/<tag>/master.m3u8?pt=<jeton>",
     *                "variantTag":"<tag>",
     *                "direct":{"url":"https://<cdn-jetable>/sunshine/<jeton>/..."}}]}
     *
     * On rend les DEUX : l'URL proxifiée par Bowd d'abord (plus stable, c'est celle que
     * leur propre player utilise), puis la directe en second serveur. Ça alimente
     * naturellement LastWorkingServer et le basculement automatique du mini-lecteur.
     *
     * Les deux portent un jeton lié à la session : elles expirent. C'est pour ça qu'on les
     * redemande à CHAQUE lecture au lieu de les mettre en cache — et pourquoi elles ne
     * peuvent pas vivre dans un M3U statique.
     */
    private suspend fun fluxHls(idChaine: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val corps = appelApi("/api/v1/channels/$idChaine/stream", "flux $idChaine")
                ?: return@withContext emptyList()
            val sorties = ArrayList<Pair<String, String>>(2)
            val arr = JSONObject(corps).optJSONArray("streams")
            if (arr != null) {
                for (k in 0 until arr.length()) {
                    val st = arr.optJSONObject(k) ?: continue
                    val q = st.optString("quality").takeIf { it.isNotBlank() && it != "auto" }
                    st.optString("url").takeIf { it.isNotBlank() }
                        ?.let { sorties.add((q ?: "direct") to it) }
                    st.optJSONObject("direct")?.optString("url")?.takeIf { it.isNotBlank() }
                        ?.let { sorties.add("source" to it) }
                    if (sorties.size >= 4) break
                }
            }
            if (sorties.isEmpty()) {
                // Filet : forme inattendue → on récupère la première m3u8 qui traîne.
                Regex("""https?://[^"\s]+\.m3u8[^"\s]*""").find(corps)?.value
                    ?.let { sorties.add("direct" to it) }
                Log.w(TAG, "forme /stream inattendue : ${corps.take(300)}")
            }
            sorties
        } catch (t: Throwable) {
            Log.w(TAG, "flux $idChaine KO : ${t.message}")
            emptyList()
        }
    }

    /**
     * Appel API authentifié, avec UNE reprise sur 401 après reconnexion complète.
     * Rend le corps, ou null. Centralisé ici pour que ni le live ni le VOD n'aient à
     * penser à l'expiration de session.
     */
    private suspend fun appelApi(chemin: String, etiquette: String): String? = withContext(Dispatchers.IO) {
        val ctx = runCatching {
            com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
        }.getOrNull() ?: return@withContext null
        var jwt = BowdAuth.jeton(ctx) ?: return@withContext null
        // Trois passages au plus : 401 → reconnexion complète, 5xx → une reprise.
        //   Leur propre client fait de même (`retry: 1` sur /vod/playable) : ce service
        //   rend des 500 par intermittence, y compris pour leur application officielle.
        var reprise5xx = false
        repeat(3) { essai ->
            try {
                val rejouer = client.newCall(requeteApi("$API$chemin", jwt)).execute().use { r ->
                    when {
                        r.isSuccessful -> return@withContext r.body?.string().orEmpty()
                        r.code == 401 && essai == 0 -> {
                            jwt = BowdAuth.jetonFrais(ctx) ?: return@withContext null
                            true
                        }
                        r.code in 500..599 && !reprise5xx -> {
                            reprise5xx = true
                            Log.d(TAG, "$etiquette : HTTP ${r.code} → une reprise")
                            true
                        }
                        else -> {
                            Log.d(TAG, "$etiquette : HTTP ${r.code}")
                            return@withContext null
                        }
                    }
                }
                if (rejouer && reprise5xx) kotlinx.coroutines.delay(1200)
            } catch (t: Throwable) {
                if (!reprise5xx) {
                    reprise5xx = true
                    Log.d(TAG, "$etiquette : ${t.message} → une reprise")
                    kotlinx.coroutines.delay(1200)
                } else {
                    Log.w(TAG, "$etiquette KO : ${t.message}")
                    return@withContext null
                }
            }
        }
        null
    }

    /** Requête API authentifiée, en-têtes que leur front envoie systématiquement. */
    private fun requeteApi(url: String, jwt: String) = okhttp3.Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $jwt")
        .header("X-Bod-Device", deviceId)
        .header("x-bod-platform", "android")
        .header("User-Agent", UA)
        .build()

    // ─────────────────────────────── VOD (source de secours par TMDB)

    @Volatile private var filmsTmdb: Set<String> = emptySet()
    @Volatile private var seriesTmdb: Set<String> = emptySet()
    @Volatile private var vodTs: Long = 0L
    private val VOD_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 2026-09-10 — Index des œuvres disponibles chez Bowd, par identifiant TMDB.
     *
     * Forme réelle de /api/v1/vod/playable (relevée connecté, 594 Ko) :
     *   {"generatedAt":"…",
     *    "movie":[["1365884","TrueFrench"],["1762506",""],…]   ← 29 436 entrées
     *    "tv":   [["314478","VF"],["246473","VF"],…]}          ←    397 entrées
     *
     * Ce sont des id TMDB bruts, sans titre ni affiche — d'où le choix (user) d'en faire
     * une SOURCE DE SECOURS plutôt qu'un dossier à parcourir : l'appariement se fait par
     * id, donc il est exact et gratuit, là où un dossier imposerait 29 436 requêtes TMDB.
     *
     * Sert de pré-filtre : sans lui, chaque film ouvert dans l'app déclencherait un appel
     * réseau vers Bowd même pour les 99 % qu'ils n'ont pas.
     */
    private suspend fun chargerIndexVod(): Boolean = withContext(Dispatchers.IO) {
        val maintenant = System.currentTimeMillis()
        if (filmsTmdb.isNotEmpty() && maintenant - vodTs < VOD_TTL_MS) return@withContext true
        val ctx = runCatching {
            com.streamflixreborn.streamflix.StreamFlixApp.instance.applicationContext
        }.getOrNull() ?: return@withContext false

        val fichier = java.io.File(ctx.cacheDir, "bowd-vod-index.txt")

        // 1) Copie disque encore fraîche → on repart de là, sans réseau.
        if (fichier.exists() && maintenant - fichier.lastModified() < VOD_TTL_MS) {
            if (lireIndexDisque(fichier)) {
                vodTs = maintenant
                Log.d(TAG, "index VOD depuis le cache disque : ${filmsTmdb.size} films, ${seriesTmdb.size} séries")
                return@withContext true
            }
        }

        // 2) Réseau. 594 Ko : leur serveur rend parfois 500 ou dépasse le délai,
        //    d'où le repli en 3) plutôt qu'un échec sec qui priverait de la source.
        val corps = appelApi("/api/v1/vod/playable", "index VOD")
        if (corps != null) {
            try {
                val o = JSONObject(corps)
                fun ids(cle: String): Set<String> {
                    val a = o.optJSONArray(cle) ?: return emptySet()
                    val out = HashSet<String>(a.length())
                    for (k in 0 until a.length()) {
                        a.optJSONArray(k)?.optString(0)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                    }
                    return out
                }
                val f = ids("movie"); val t = ids("tv")
                if (f.isNotEmpty() || t.isNotEmpty()) {
                    filmsTmdb = f; seriesTmdb = t; vodTs = maintenant
                    runCatching {
                        fichier.writeText(f.joinToString(",") + "\n" + t.joinToString(","))
                    }
                    Log.i(TAG, "index VOD Bowd : ${f.size} films, ${t.size} séries")
                    return@withContext true
                }
            } catch (t: Throwable) { Log.w(TAG, "index VOD illisible : ${t.message}") }
        }

        // 3) Réseau en panne → copie disque même périmée. Un index d'hier vaut
        //    infiniment mieux que pas de source du tout : le catalogue bouge peu.
        if (fichier.exists() && lireIndexDisque(fichier)) {
            vodTs = maintenant - VOD_TTL_MS + 10 * 60 * 1000L  // on retentera dans 10 min
            Log.i(TAG, "index VOD : réseau KO → cache périmé (${filmsTmdb.size} films)")
            return@withContext true
        }
        false
    }

    private fun lireIndexDisque(fichier: java.io.File): Boolean = try {
        val lignes = fichier.readLines()
        val f = lignes.getOrNull(0)?.split(",")?.filter { it.isNotBlank() }?.toHashSet() ?: HashSet()
        val t = lignes.getOrNull(1)?.split(",")?.filter { it.isNotBlank() }?.toHashSet() ?: HashSet()
        if (f.isEmpty() && t.isEmpty()) false
        else { filmsTmdb = f; seriesTmdb = t; true }
    } catch (_: Throwable) { false }

    /**
     * Serveurs Bowd pour un film ou un épisode, appariés par identifiant TMDB.
     *
     * Contrat relevé dans leur bundle web (buildVodStreamPath / episodeQuerySuffix) :
     *   /api/v1/vod/<movie|tv>/<tmdbId>/streams[?sa=<saison>&epi=<épisode>]
     * Le type et l'id sont dans le CHEMIN — c'est pour ça que les tentatives en
     * paramètres de requête renvoyaient toutes 404.
     */
    suspend fun serveursVod(
        tmdbId: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
    ): List<Video.Server> = withContext(Dispatchers.IO) {
        if (tmdbId.isBlank() || !tmdbId.all { it.isDigit() }) return@withContext emptyList()
        // 2026-09-10 (user : « je suspecte que 397 séries ne soit pas exact ») — vérifié
        //   dans leur bundle : `decideSeriesPlayable` ne consulte PAS cet index, elle se
        //   fie à `hasEpisode`/`fluxCount`, donc au résultat d'un appel réel épisode par
        //   épisode. Pour les séries, l'index n'est qu'un indicateur d'affichage : un
        //   filtre strict dessus écarterait des séries qui répondent très bien.
        //   → Films : filtre conservé (29 436 entrées, index manifestement complet, et
        //     ça évite un appel réseau inutile sur l'immense majorité des fiches).
        //   → Séries : on tente l'appel quoi qu'il arrive.
        val indexPret = chargerIndexVod()
        if (isMovie && !indexPret) return@withContext emptyList()
        val presente = if (isMovie) tmdbId in filmsTmdb else true
        if (!presente) {
            // Trace explicite : sans elle, « pas au catalogue » et « échec réseau »
            //   produisent tous deux un silence, indistinguables au diagnostic.
            Log.d(TAG, "VOD Bowd : tmdb $tmdbId (${if (isMovie) "film" else "série"}) absent de leur catalogue")
            return@withContext emptyList()
        }

        val type = if (isMovie) "movie" else "tv"
        val epi = if (isMovie) "" else "&sa=$season&epi=$episode"

        // Étape 1 — variantes disponibles. Forme réelle (relevée connecté) :
        //   {"streams":[{"index":1,"lang":"VF","token":"…"}, …]}
        //   Pas d'URL ici : juste l'index de flux et la langue.
        val listing = appelApi("/api/v1/vod/$type/$tmdbId/streams${epi.replaceFirst("&", "?")}",
            "variantes $type/$tmdbId") ?: return@withContext emptyList()

        data class Variante(val index: Int, val lang: String)
        val variantes = ArrayList<Variante>(4)
        try {
            val arr = JSONObject(listing).optJSONArray("streams")
            if (arr != null) for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val idx = o.optInt("index", -1).takeIf { it >= 0 } ?: continue
                variantes.add(Variante(idx, o.optString("lang").ifBlank { "VF" }))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "variantes $type/$tmdbId illisibles : ${t.message}")
            return@withContext emptyList()
        }
        if (variantes.isEmpty()) {
            // Dernier chemin silencieux comblé : sans cette trace, une réponse 200 au
            //   contenu inattendu était indiscernable d'une panne réseau.
            Log.w(TAG, "variantes $type/$tmdbId : aucune (${listing.length} o)")
            return@withContext emptyList()
        }

        // VF d'abord (règle maison : pas de VOSTFR servi comme du VF), puis le reste.
        val ordre = variantes.sortedBy {
            when (it.lang.uppercase()) { "VF" -> 0; "TRUEFRENCH" -> 1; "VOSTFR" -> 2; else -> 3 }
        }
        Log.d(TAG, "variantes $type/$tmdbId : ${ordre.size} (${ordre.take(3).joinToString { it.lang }}…)")

        // Étape 2 — URL réelle par variante :
        //   /api/v1/vod/<type>/<tmdb>/stream?flux=<index>[&sa=&epi=][&pref=<lang>]
        //   (contrat lu dans leur bundle : buildVodStreamPath + episodeQuerySuffix + prefQuerySuffix)
        // Résolution EN PARALLÈLE : en séquentiel, trois variantes dont une en 404
        //   coûtaient une trentaine de secondes (mesuré). On se limite à deux — la VF
        //   d'abord — et on les lance ensemble. Trois plutôt que deux : ils exposent
        //   jusqu'à 25 variantes et certaines sont mortes (404 constaté sur #2), donc
        //   une de plus coûte zéro temps mais évite de finir avec un seul serveur.
        val out = java.util.Collections.synchronizedList(ArrayList<Video.Server>(4))
        kotlinx.coroutines.coroutineScope {
            val portee = this
            ordre.take(3).map { v -> portee.async {
            val pref = if (v.lang.uppercase() in setOf("VF", "VOSTFR", "VO", "ES")) "&pref=${v.lang.uppercase()}" else ""
            val corps = appelApi("/api/v1/vod/$type/$tmdbId/stream?flux=${v.index}$epi$pref",
                "flux VOD $type/$tmdbId#${v.index}") ?: return@async
            var trouve = false
            try {
                val arr = JSONObject(corps).optJSONArray("streams")
                if (arr != null) for (k in 0 until arr.length()) {
                    val st = arr.optJSONObject(k) ?: continue
                    st.optString("url").takeIf { it.isNotBlank() }?.let {
                        out.add(Video.Server(id = "bowd::vod::$tmdbId::${out.size}", name = "${v.lang}", src = it)); trouve = true
                    }
                    st.optJSONObject("direct")?.optString("url")?.takeIf { it.isNotBlank() }?.let {
                        out.add(Video.Server(id = "bowd::vod::$tmdbId::${out.size}", name = "${v.lang} · source", src = it)); trouve = true
                    }
                }
            } catch (_: Throwable) {}
            if (!trouve) {
                Regex("""https?://[^"\s]+\.m3u8[^"\s]*""").find(corps)?.value?.let {
                    out.add(Video.Server(id = "bowd::vod::$tmdbId::${out.size}", name = "${v.lang}", src = it)); trouve = true
                }
            }
            if (!trouve) Log.w(TAG, "flux VOD $type/$tmdbId#${v.index} : aucune URL exploitable")
            } }.forEach { it.await() }
        }
        if (out.isNotEmpty()) Log.i(TAG, "VOD Bowd $type/$tmdbId → ${out.size} serveurs")
        out
    }

    /** URL de la page player pour un id de serveur (utilisé par getVideo pour lancer la WebView). */
    fun pagePlayerDe(serverId: String): String = "$SITE/player/${idChaine(serverId)}"
}
