package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 2026-10-31 — ESSAI « base d'index » (Worker onyx-index-test + base D1 dédiée).
 * Pour un film (ou un épisode), la base dit :
 *   · pages  : sur quelle PAGE de chaque site il se trouve (collecte du Worker, étiquetage TMDB
 *              vérifié) ou quelle clé a été SIGNALÉE par l'appli (« id:… », correspondance sûre) ;
 *   · serveurs : la liste de serveurs déjà trouvée par une source aux liens STABLES (≤ 7 jours).
 * L'appli va alors directement à la page / sert les serveurs au lieu de chercher par titre.
 * Rien trouvé, base lente ou injoignable → la recherche habituelle, inchangée (aucune casse).
 */
object IndexOnyx {
    private const val TAG = "IndexOnyx"
    private const val URL_BASE = "https://onyx-index-test.logami61250.workers.dev"
    private const val DELAI_MS = 600L

    /** Sources dont les serveurs restent valables des jours (pages vidéo, fiches, lecteurs
     *  embarqués) : leur liste de serveurs peut être mémorisée et resservie telle quelle.
     *  Exclues : liens signés qui expirent (Coflix, Purstream, Moviebox, NetMirror…). */
    val SERVEURS_STABLES = setOf(
        "ok.ru", "Rutube", "Tokyvideo", "archive.org", "Pluto",
        "GoPlay", "KissKH", "Adkami", "iAnime", "Vostfree", "DessinAnimeNet",
        "Papadustream V2", "Yablom",
        "Plex", "OTF TV", "FileSearch", "TV Hub", "AniCloud",
        // NetMirror : src « nm::<plateforme>::<id>[::sX::eY] », cookies fournis à la LECTURE.
        "NetMirror",
    )
    // ⚠ « Mon IPTV » volontairement ABSENT : ses adresses contiennent les identifiants de
    //   l'abonnement IPTV personnel du user → ne doivent JAMAIS partir dans une base partagée.

    data class Page(val site: String, val chemin: String, val titre: String, val annee: Int?)
    data class Reponse(val pages: List<Page>, val serveurs: Map<String, List<Video.Server>>) {
        fun connait(site: String) = pages.any { it.site.equals(site, true) } || serveurs.keys.any { it.equals(site, true) }
    }
    private val VIDE = Reponse(emptyList(), emptyMap())

    private val client = OkHttpClient.Builder().callTimeout(DELAI_MS, TimeUnit.MILLISECONDS).build()
    private val clientEnvoi = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Une seule question par film/épisode, partagée par toutes les sources (cache 10 min). */
    private val cache = ConcurrentHashMap<String, Pair<Long, Reponse>>()

    private fun idValide(tmdbId: String?) = tmdbId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    suspend fun interroger(tmdbId: String?, film: Boolean, saison: Int = 0, episode: Int = 0): Reponse {
        val id = idValide(tmdbId) ?: return VIDE
        val s = if (film) 0 else saison; val e = if (film) 0 else episode
        val cle = "$id|$film|$s|$e"
        cache[cle]?.let { (quand, r) -> if (System.currentTimeMillis() - quand < 10 * 60_000L) return r }
        return withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                val url = "$URL_BASE/cherche?tmdb=$id&type=${if (film) "movie" else "tv"}&s=$s&e=$e"
                client.newCall(Request.Builder().url(url).build()).execute().use { rep ->
                    // 2026-11-01 : vu une fois « 0 page » alors que la base avait 8 pages → on note
                    //   le code reçu, sinon un refus du Worker passe inaperçu dans le journal.
                    if (!rep.isSuccessful) { Log.w(TAG, "base : HTTP ${rep.code} pour tmdb=$id s=$s e=$e → recherche habituelle"); return@use VIDE }
                    val o = JSONObject(rep.body?.string().orEmpty())
                    val arr = o.optJSONArray("pages") ?: JSONArray()
                    val pages = (0 until arr.length()).mapNotNull { i ->
                        val p = arr.optJSONObject(i) ?: return@mapNotNull null
                        Page(p.optString("site"), p.optString("chemin"), p.optString("titre"), p.optInt("annee").takeIf { it > 0 })
                    }
                    val serveurs = HashMap<String, List<Video.Server>>()
                    o.optJSONObject("serveurs")?.let { obj ->
                        for (site in obj.keys()) {
                            val l = obj.optJSONArray(site) ?: continue
                            serveurs[site] = (0 until l.length()).mapNotNull { i ->
                                val x = l.optJSONObject(i) ?: return@mapNotNull null
                                val m = x.optJSONArray("mirrors")
                                Video.Server(id = x.optString("id"), name = x.optString("name"), src = x.optString("src"),
                                    mirrors = if (m == null) emptyList() else (0 until m.length()).map { m.optString(it) })
                            }
                        }
                    }
                    Reponse(pages, serveurs)
                }
            }.onFailure { Log.w(TAG, "base injoignable (${System.currentTimeMillis() - t0} ms) : ${it.message} → recherche habituelle") }
                .getOrDefault(VIDE)
            Log.i(TAG, "tmdb=$id s=$s e=$e → ${r.pages.size} page(s) ${r.pages.map { "${it.site}:${it.chemin}" }}, serveurs mémorisés ${r.serveurs.mapValues { it.value.size }} en ${System.currentTimeMillis() - t0} ms")
            cache[cle] = System.currentTimeMillis() to r
            r
        }
    }

    // ── Film/épisode en cours de collecte ─────────────────────────────────────────────
    @Volatile private var derniere: Reponse = VIDE
    @Volatile private var enCours: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile private var courant: Triple<String, Boolean, Pair<Int, Int>>? = null

    /** À appeler dès le début d'une collecte (remplit le cache et [derniere]). */
    suspend fun prechauffer(tmdbId: String?, film: Boolean, saison: Int = 0, episode: Int = 0) {
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        enCours = signal
        derniere = VIDE
        courant = idValide(tmdbId)?.let { Triple(it, film, (if (film) 0 else saison) to (if (film) 0 else episode)) }
        try { derniere = interroger(tmdbId, film, saison, episode) } finally { signal.complete(Unit) }
    }

    fun oublier() { enCours = null; derniere = VIDE; courant = null }

    /** La source a-t-elle sa page ou ses serveurs connus ? (attend la base au plus [DELAI_MS]) */
    suspend fun connaitDeja(site: String): Boolean {
        enCours?.let { kotlinx.coroutines.withTimeoutOrNull(DELAI_MS) { it.await() } }
        return derniere.connait(site)
    }

    // ── Mémoire des serveurs (sources aux liens stables) ──────────────────────────────
    /** Enveloppe la recherche d'une source : serveurs mémorisés servis tout de suite, sinon
     *  recherche habituelle puis mémorisation de ce qu'elle trouve. */
    fun avecMemoire(source: String, fetch: suspend () -> List<Video.Server>): suspend () -> List<Video.Server> {
        if (source !in SERVEURS_STABLES) return fetch
        return memo@{
            enCours?.let { kotlinx.coroutines.withTimeoutOrNull(DELAI_MS) { it.await() } }
            val c = courant
            val connus = derniere.serveurs.entries.firstOrNull { it.key.equals(source, true) }?.value.orEmpty()
            if (c != null && connus.isNotEmpty()) {
                Log.i(TAG, "$source → ${connus.size} serveur(s) mémorisé(s) servis sans recherche")
                return@memo connus
            }
            val trouves = fetch()
            if (c != null && trouves.isNotEmpty()) memoriser(source, c.first, c.second, c.third.first, c.third.second, trouves)
            trouves
        }
    }

    private fun memoriser(site: String, tmdb: String, film: Boolean, s: Int, e: Int, serveurs: List<Video.Server>) {
        portee.launch {
            runCatching {
                val arr = JSONArray()
                serveurs.take(40).forEach { sv ->
                    arr.put(JSONObject().put("id", sv.id).put("name", sv.name).put("src", sv.src).put("mirrors", JSONArray(sv.mirrors)))
                }
                val corps = JSONObject().put("site", site).put("tmdb", tmdb.toInt()).put("type", if (film) "movie" else "tv")
                    .put("s", s).put("e", e).put("serveurs", arr).toString()
                clientEnvoi.newCall(Request.Builder().url("$URL_BASE/memoriser")
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), corps)).build())
                    .execute().use { Log.i(TAG, "mémorisé $site : ${serveurs.size} serveur(s) tmdb=$tmdb s=$s e=$e → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "mémorisation $site échouée : ${it.message}") }
        }
    }

    // ── Mémoire participative (correspondances sûres) ────────────────────────────────
    private val dejaSignales = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** [saison] : pour une série, la page signalée vaut pour TOUTE la saison (épisode 0). */
    fun signaler(site: String, cle: String, tmdbId: String?, film: Boolean, titre: String?, saison: Int = 0) {
        val id = idValide(tmdbId) ?: return
        val s = if (film) 0 else saison
        if (cle.isBlank() || !dejaSignales.add("$site|$cle|$id|$s")) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("site", site).put("cle", cle).put("tmdb", id.toInt())
                    .put("type", if (film) "movie" else "tv").put("s", s).put("titre", titre.orEmpty()).toString()
                clientEnvoi.newCall(Request.Builder().url("$URL_BASE/signaler")
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), corps)).build())
                    .execute().use { Log.i(TAG, "signalé $site '$cle' = tmdb $id${if (s > 0) " saison $s" else ""} → HTTP ${it.code}") }
            }.onFailure { Log.w(TAG, "signalement $site échoué : ${it.message}") }
        }
    }

    /** 2026-10-31 — La page donnée par la base pour ce site a été REFUSÉE par les garde-fous
     *  (titre/année/type). On le dit à la base : quand 2 appareils différents ont refusé, elle
     *  retire ce qu'elle avait pour ce site et ce film. Une fois par site et film par session.
     *  ⚠ Uniquement pour un refus de correspondance — PAS pour « 0 serveur » (site en panne). */
    private val dejaRefuses = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun refuser(site: String, tmdbId: String?, film: Boolean, saison: Int = 0) {
        val id = idValide(tmdbId) ?: return
        val s = if (film) 0 else saison
        val chemin = derniere.pages.firstOrNull { it.site.equals(site, ignoreCase = true) }?.chemin ?: return
        if (!dejaRefuses.add("$site|$id|$s")) return
        portee.launch {
            runCatching {
                val corps = JSONObject().put("site", site).put("tmdb", id.toInt())
                    .put("type", if (film) "movie" else "tv").put("s", s).put("chemin", chemin).toString()
                clientEnvoi.newCall(Request.Builder().url("$URL_BASE/refuser")
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), corps)).build())
                    .execute().use { Log.i(TAG, "refusé $site '$chemin' tmdb $id${if (s > 0) " saison $s" else ""} → HTTP ${it.code} ${it.body?.string().orEmpty()}") }
            }.onFailure { Log.w(TAG, "refus $site échoué : ${it.message}") }
        }
    }

    /** Clé signalée (« id:… ») par l'appli pour ce site et ce film — ou cette SÉRIE entière
     *  (clé au niveau de la série, saison/épisode 0), telle quelle, ou null. */
    suspend fun cleSignalee(site: String, tmdbId: String?, film: Boolean): String? {
        return interroger(tmdbId, film).pages
            .firstOrNull { it.site.equals(site, ignoreCase = true) && it.chemin.startsWith("id:") }
            ?.chemin?.removePrefix("id:")
    }

    // ── Accès direct aux pages ───────────────────────────────────────────────────────
    /** Chemin de la page d'un site pour ce film (ex. « /movie/l-odyssee »), ou null. */
    suspend fun cheminDe(site: String, tmdbId: String?, film: Boolean): String? {
        if (!film) return null
        return interroger(tmdbId, film).pages.firstOrNull { it.site.equals(site, ignoreCase = true) }?.chemin
            ?.takeIf { !it.startsWith("id:") }
    }

    /** Fiche à donner à une source À LA PLACE de sa recherche par titre, ou null.
     *  Chemin collecté → dernier morceau (Wiflix « …/36537-x.html » → « 36537-x.html »,
     *  Firestorm « /films/le-slug/ » → « le-slug ») ; « id:… » = clé signalée, telle quelle. */
    suspend fun ficheDirecte(site: String, tmdbId: String?, film: Boolean, saison: Int = 0, episode: Int = 0): com.streamflixreborn.streamflix.adapters.AppAdapter.Item? {
        if (!film) {
            // 2026-10-31 — Séries : uniquement les pages SIGNALÉES par l'appli pour CETTE saison
            //   (la base les renvoie pour tout épisode de la saison). Mêmes paramètres que
            //   prechauffer() → réponse déjà en cache.
            if (saison <= 0) return null
            val p = interroger(tmdbId, false, saison, episode).pages
                .firstOrNull { it.site.equals(site, ignoreCase = true) && it.chemin.startsWith("id:") } ?: return null
            return com.streamflixreborn.streamflix.models.TvShow(id = p.chemin.removePrefix("id:"), title = p.titre)
        }
        val p = interroger(tmdbId, film).pages.firstOrNull { it.site.equals(site, ignoreCase = true) } ?: return null
        val id = if (p.chemin.startsWith("id:")) p.chemin.removePrefix("id:") else p.chemin.trimEnd('/').substringAfterLast('/')
        return com.streamflixreborn.streamflix.models.Movie(id = id, title = p.titre)
    }
}
