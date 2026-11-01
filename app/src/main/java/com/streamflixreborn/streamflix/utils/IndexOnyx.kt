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
    // 2026-11-01 (Bleach S2E46 sur la télé : « base injoignable (616 ms) : timeout » alors que le
    //   Worker répond en 30 ms) — quand 40 sources partent en même temps sur une box TV, la
    //   réponse met plus de 600 ms à arriver et la base était perdue. 4 s (relevé sur l'Oppo :
    //   2,5 s ne suffisaient pas toujours) : les recherches des sources prennent de toute façon
    //   5 à 30 s, et l'attente se fait en parallèle.
    private const val DELAI_MS = 4_000L

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

    /** [saison] : 0 = fiche de toute la série ; N > 0 = fiche propre à la saison N (ex. FRAnime
     *  « Bleach: Sennen Kessen-hen » = TMDB Bleach saison 2) ; -1 = non précisé. */
    data class Page(val site: String, val chemin: String, val titre: String, val annee: Int?, val saison: Int = -1)

    // 2026-11-01 (user, Bleach Mille Ans) — fiches données par la base qui ne contiennent QU'UNE
    //   saison TMDB : chez la source, cette saison TMDB est sa « Saison 1 ». Clé « site|id ».
    private val fichesDeSaison = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun estFicheDeSaison(site: String, id: String) = (site.lowercase() + "|" + id) in fichesDeSaison
    /** 2026-11-01 : fiche d'un AUTRE site reconnue comme propre à une saison (cf. titresDeSaison). */
    fun marquerFicheDeSaison(site: String, id: String) { fichesDeSaison += site.lowercase() + "|" + id }

    /** 2026-11-01 (user, Bleach Mille Ans chez VoirAnime / Vostfree) — titres des fiches PROPRES à
     *  cette saison que la base connaît déjà ailleurs (ex. FRAnime « BLEACH: Sennen Kessen-hen »
     *  = TMDB 30984 saison 2). Les autres sites rangent souvent cette saison sous le même titre. */
    suspend fun titresDeSaison(tmdbId: String?, saison: Int, episode: Int): List<String> {
        if (saison <= 1) return emptyList()
        val pages = interroger(tmdbId, false, saison, episode).pages
        fun n(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        // Un titre porté AUSSI par une page de toute la série (saison 0/1, ex. AnimeSama « Bleach »)
        //   n'est pas un titre de saison : c'est la page de la série signalée pour cette saison.
        val titresSerie = pages.filter { it.saison in 0..1 }.map { n(it.titre) }.toSet()
        return pages.filter { it.saison == saison && it.titre.isNotBlank() && n(it.titre) !in titresSerie }
            .map { it.titre }.distinctBy { n(it) }
    }
    /** [ages] (2026-11-01, base « un serveur par ligne ») : heures depuis la dernière recherche qui
     *  a renvoyé un serveur de ce site — sert à décider s'il faut rafraîchir (cf. avecMemoire). */
    data class Reponse(val pages: List<Page>, val serveurs: Map<String, List<Video.Server>>, val ages: Map<String, Int> = emptyMap()) {
        fun connait(site: String) = pages.any { it.site.equals(site, true) } || serveurs.keys.any { it.equals(site, true) }
    }
    private val VIDE = Reponse(emptyList(), emptyMap())

    // 2026-11-01 (Oppo : 3-4 s pour une réponse qui en prend 0,03 au Worker, ping 12 ms) — DIAGNOSTIC :
    //   on chronomètre chaque étape de l'appel (DNS, connexion, TLS, attente de la réponse) pour
    //   savoir où passe le temps. Journal seulement si l'appel dépasse 1 s.
    private class ChronoEtapes : okhttp3.EventListener() {
        private val t0 = System.currentTimeMillis()
        private val etapes = StringBuilder()
        private fun note(n: String) { etapes.append(n).append('=').append(System.currentTimeMillis() - t0).append("ms ") }
        override fun dnsStart(call: okhttp3.Call, domainName: String) = note("dnsDébut")
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = note("dnsFin")
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = note("connexion")
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = note("tlsFin")
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = note("connecté")
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) = note("réponse")
        private fun fin(quoi: String) {
            val total = System.currentTimeMillis() - t0
            if (total > 1000) Log.w(TAG, "base lente ($quoi en $total ms) : $etapes")
        }
        override fun callEnd(call: okhttp3.Call) = fin("ok")
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = fin("échec ${ioe.message}")
    }
    private val client = OkHttpClient.Builder().callTimeout(DELAI_MS, TimeUnit.MILLISECONDS)
        .eventListenerFactory { ChronoEtapes() }.build()
    /** 2ᵉ essai : connexions À PART (une connexion restée accrochée au Worker figé n'est pas réutilisée). */
    private val clientNeuf = OkHttpClient.Builder().callTimeout(DELAI_MS, TimeUnit.MILLISECONDS)
        .connectionPool(okhttp3.ConnectionPool()).eventListenerFactory { ChronoEtapes() }.build()
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
        // 2026-11-01 (Oppo, Bleach S2E44 : 13 « base injoignable (2,5 s) » à la même milliseconde) —
        //   chaque source posait SA question en même temps → 13 connexions parallèles au démarrage,
        //   toutes perdues. Une seule question en vol par épisode, partagée par toutes les sources.
        enVol[cle]?.let { return it.await() }
        val attente = kotlinx.coroutines.CompletableDeferred<Reponse>()
        enVol.putIfAbsent(cle, attente)?.let { return it.await() }
        try {
            return interrogerReseau(id, film, s, e, cle).also { attente.complete(it) }
        } catch (t: Throwable) {
            attente.complete(VIDE); throw t
        } finally { enVol.remove(cle, attente) }
    }

    private val enVol = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Reponse>>()

    private suspend fun interrogerReseau(id: String, film: Boolean, s: Int, e: Int, cle: String): Reponse {
        return withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            // 2026-11-01 (journal du Worker : la plupart des réponses en 30-60 ms, mais de temps en temps
            //   le Worker reste figé 4 à 13 s ; une 2ᵉ question posée juste après passe en 89 ms) — si la
            //   1ʳᵉ tentative échoue (délai dépassé / réseau), on en refait UNE sur une connexion neuve.
            //   Sans la base, FRAnime/FrenchManga perdent leur fiche de saison (Bleach : 2 serveurs de moins).
            var essai = 0
            var r: Reponse = VIDE
            while (essai < 2) {
                essai++
                val t1 = System.currentTimeMillis()
                val tentative = runCatching {
                // v=2 : tous les serveurs confirmés, quel que soit leur âge, + leur âge par site.
                val url = "$URL_BASE/cherche?tmdb=$id&type=${if (film) "movie" else "tv"}&s=$s&e=$e&v=2"
                (if (essai == 1) client else clientNeuf).newCall(Request.Builder().url(url).build()).execute().use { rep ->
                    // 2026-11-01 : vu une fois « 0 page » alors que la base avait 8 pages → on note
                    //   le code reçu, sinon un refus du Worker passe inaperçu dans le journal.
                    if (!rep.isSuccessful) { Log.w(TAG, "base : HTTP ${rep.code} pour tmdb=$id s=$s e=$e → recherche habituelle"); return@use VIDE }
                    val corps = rep.body?.string().orEmpty()
                    val o = JSONObject(corps)
                    val arr = o.optJSONArray("pages") ?: JSONArray()
                    // 2026-11-01 : « 0 page » revu sur Bleach S2E46 alors que le Worker en rend 6
                    //   depuis le PC → on garde la réponse brute pour comprendre d'où ça vient.
                    if (arr.length() == 0) Log.w(TAG, "base : 0 page pour tmdb=$id s=$s e=$e — HTTP ${rep.code}, " +
                        "hôte ${rep.request.url.host}, réponse ${corps.length} car. : ${corps.take(300)}")
                    val pages = (0 until arr.length()).mapNotNull { i ->
                        val p = arr.optJSONObject(i) ?: return@mapNotNull null
                        Page(p.optString("site"), p.optString("chemin"), p.optString("titre"), p.optInt("annee").takeIf { it > 0 },
                            if (p.has("saison") && !p.isNull("saison")) p.optInt("saison", -1) else -1)
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
                    val ages = HashMap<String, Int>()
                    o.optJSONObject("ages")?.let { obj -> for (site in obj.keys()) ages[site] = obj.optInt(site) }
                    Reponse(pages, serveurs, ages)
                }
            }
                if (tentative.isSuccess) { r = tentative.getOrDefault(VIDE); break }
                Log.w(TAG, "base injoignable (essai $essai, ${System.currentTimeMillis() - t1} ms) : ${tentative.exceptionOrNull()?.message}" +
                    if (essai < 2) " → nouvel essai" else " → recherche habituelle")
            }
            Log.i(TAG, "tmdb=$id s=$s e=$e → ${r.pages.size} page(s) ${r.pages.map { "${it.site}:${it.chemin}" }}, serveurs mémorisés ${r.serveurs.mapValues { it.value.size }} en ${System.currentTimeMillis() - t0} ms")
            // Un échec (délai dépassé, réseau) n'est PAS retenu : sinon « rien » restait 10 min.
            if (r !== VIDE) cache[cle] = System.currentTimeMillis() to r
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

    /** Fiche en cours (tmdb, film ?, saison/épisode) — pour le signalement des fichiers morts. */
    fun ficheCourante(): Triple<String, Boolean, Pair<Int, Int>>? = courant

    /** La source a-t-elle sa page ou ses serveurs connus ? (attend la base au plus [DELAI_MS]) */
    suspend fun connaitDeja(site: String): Boolean {
        enCours?.let { kotlinx.coroutines.withTimeoutOrNull(2 * DELAI_MS + 500) { it.await() } }
        return derniere.connait(site)
    }

    // ── Mémoire des serveurs (sources aux liens stables) ──────────────────────────────
    /** Enveloppe la recherche d'une source : serveurs mémorisés servis tout de suite, sinon
     *  recherche habituelle puis mémorisation de ce qu'elle trouve. */
    fun avecMemoire(source: String, fetch: suspend () -> List<Video.Server>): suspend () -> List<Video.Server> {
        if (source !in SERVEURS_STABLES) return fetch
        return memo@{
            enCours?.let { kotlinx.coroutines.withTimeoutOrNull(2 * DELAI_MS + 500) { it.await() } }
            val c = courant
            val connus = derniere.serveurs.entries.firstOrNull { it.key.equals(source, true) }?.value.orEmpty()
            // 2026-11-01 (plan « base auto-entretenue ») — la base garde chaque serveur tant qu'il
            //   existe ; c'est l'appli qui la rafraîchit : au-delà de [ageMaxHeures], on sert les
            //   serveurs connus ET on refait la recherche, pour ajouter ce qui est apparu depuis.
            //   La recherche ne fait qu'AJOUTER : un échec ou une liste vide n'envoie rien et
            //   n'enlève rien (le Worker ne retire jamais un serveur absent d'un envoi).
            val age = derniere.ages.entries.firstOrNull { it.key.equals(source, true) }?.value
            if (c != null && connus.isNotEmpty()) {
                if (age == null || age < ageMaxHeures(source)) {
                    Log.i(TAG, "$source → ${connus.size} serveur(s) mémorisé(s) servis sans recherche (vus il y a ${age ?: "?"} h)")
                    return@memo connus
                }
                val trouves = runCatching { fetch() }.getOrElse {
                    Log.w(TAG, "$source : rafraîchissement échoué (${it.message}) → serveurs mémorisés seuls"); emptyList()
                }
                if (trouves.isNotEmpty()) memoriser(source, c.first, c.second, c.third.first, c.third.second, trouves)
                val dejaVus = trouves.map { it.id }.toHashSet()
                val union = trouves + connus.filter { it.id !in dejaVus }
                Log.i(TAG, "$source → rafraîchi (vus il y a $age h) : ${trouves.size} trouvé(s), ${union.size - trouves.size} mémorisé(s) en plus")
                return@memo union
            }
            val trouves = fetch()
            if (c != null && trouves.isNotEmpty()) memoriser(source, c.first, c.second, c.third.first, c.third.second, trouves)
            trouves
        }
    }

    /** Âge au-delà duquel on refait la recherche d'une source mémorisée. FileSearch indexe des
     *  fichiers nouveaux chaque jour → 2 jours ; les autres changent peu → 7 jours (= l'ancien délai). */
    private fun ageMaxHeures(source: String): Int = if (source.equals("FileSearch", true)) 48 else 168

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
            val candidates = interroger(tmdbId, false, saison, episode).pages
                .filter { it.site.equals(site, ignoreCase = true) && it.chemin.startsWith("id:") }
            // La fiche PROPRE à cette saison d'abord (saison > 1 seulement), sinon celle de toute la série.
            val p = candidates.firstOrNull { saison > 1 && it.saison == saison } ?: candidates.firstOrNull() ?: return null
            val id = p.chemin.removePrefix("id:")
            if (saison > 1 && p.saison == saison) {
                fichesDeSaison += site.lowercase() + "|" + id
                Log.i(TAG, "$site : fiche propre à la saison $saison → id=$id (sa saison 1 = notre saison $saison)")
            }
            return com.streamflixreborn.streamflix.models.TvShow(id = id, title = p.titre)
        }
        val p = interroger(tmdbId, film).pages.firstOrNull { it.site.equals(site, ignoreCase = true) } ?: return null
        val id = if (p.chemin.startsWith("id:")) p.chemin.removePrefix("id:") else p.chemin.trimEnd('/').substringAfterLast('/')
        return com.streamflixreborn.streamflix.models.Movie(id = id, title = p.titre)
    }
}
