package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * RénéVéo (reneveo.store) — ~40 chaînes FR en direct, dossier du TV Hub à côté de Stream4Free.
 *
 * 2026-09-06 (user : « j'ai trouvé un site, peut-être qu'on peut faire la même chose que
 * Stream4Free… pour accéder à chaque chaîne il faut cliquer 4 ou 5 fois sur une pub, mais
 * peut-être que ça peut être passé »). Analyse du site :
 *   • Le « clique N fois sur la pub » est PUREMENT côté navigateur : un compteur JavaScript
 *     qui ouvre un lien sponsorisé à chaque clic, puis appelle `unlockPlayer()`. Le serveur
 *     ne vérifie rien — on n'a donc aucune pub à passer, on ne charge jamais la page.
 *   • La liste des chaînes vient d'une base Supabase publique (clé anonyme écrite en clair
 *     dans le HTML de `/live`, table `channels` : id, name, logo, category, active,
 *     source_1..3_url/type). On extrait la clé de la page à l'ouverture du dossier plutôt
 *     que de la coder en dur : si elle tourne, on suit. Un CSV de secours existe
 *     (`/assets/serv/channels_rows.csv`) mais il date d'avril → dernier recours seulement.
 *   • Les flux passent par `live.reneveo.store` (proxy « vavoo », « flix », « empire ») ou par
 *     des sources publiques (France TV via schumijo, Arte akamai, BFM, CNews…). La SEULE
 *     protection côté serveur est l'en-tête `Referer: https://reneveo.store/` : sans lui,
 *     le proxy sert une vidéo LEURRE (mp4 de 900 Ko, ou une playlist « messMAJ » dont tous
 *     les segments ont le même MD5) ; avec lui, il répond une redirection 302 vers un CDN
 *     (`*.ngolpdkyoctjcddxshli469r.org/sunshine/<jeton>/hls/index.m3u8`, jeton ~25 min)
 *     que le lecteur suit tout seul. Vérifié en ligne de commande : vrais segments TS
 *     (« FFmpeg Service01 »), TF1 en cours. À l'expiration du jeton, le lecteur repart de
 *     l'URL d'origine (nouvelle 302, nouveau jeton) — rien à renouveler à la main.
 *   • Jusqu'à 3 sources par chaîne, à garder toutes : le jour de l'analyse, la source
 *     « hls » directe de TF1 (tvradiozap) était en « message de mise à jour » alors que la
 *     source « vavoo » marchait.
 *
 * Identifiants : chaîne `livehub::reneveo::<id>`, serveur `livehub::reneveo::<id>::<n>`
 * (n = 1..3, libellé « RénéVéo · serveur n », jamais le nom du proxy), dossier `reneveo`.
 */
object ReneveoTv {

    private const val TAG = "ReneveoTv"
    const val SITE = "https://reneveo.store"
    const val PREFIX = "livehub::reneveo::"
    const val FOLDER_KEY = "reneveo"
    const val LOGO = "$SITE/assets/favicon.png"
    private const val PAGE_LIVE = "$SITE/live"
    private const val CSV_SECOURS = "$SITE/assets/serv/channels_rows.csv"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val SELECT =
        "id,name,logo,slug,category,sort_order,active,source_1_url,source_1_type,source_2_url,source_2_type,source_3_url,source_3_type"

    /** Catégories du site, dans l'ordre où il les affiche. */
    private val CATEGORIES = listOf(
        "generaliste" to "Généraliste",
        "sport" to "Sport",
        "information" to "Info",
        "cinema" to "Cinéma",
        "documentaire" to "Documentaire",
        "jeunesse" to "Jeunesse",
        "divers" to "Divers",
    )

    data class Chaine(
        val id: String,
        val nom: String,
        val logo: String,
        val categorie: String,
        val tri: Int,
        /** URLs des sources 1..3, dans l'ordre du site (vides retirées). */
        val sources: List<String>,
    )

    @Volatile private var cache: List<Chaine> = emptyList()
    @Volatile private var cacheTs = 0L
    private val verrou = Mutex()
    /** Clé/URL Supabase : d'abord celles embarquées (assets), remplacées par celles lues dans la page si elles ne marchent plus. */
    @Volatile private var supabase: Pair<String, String>? = null
    @Volatile private var rafraichissementEnCours = false

    /**
     * 2026-09-06 (user : « peut-être qu'on devrait coder les chaînes en dur vu qu'elles changent
     * pas… à moins que tu fasses un petit truc pour ajouter automatiquement s'il y a des
     * nouvelles ») : les deux. La liste du jour est EMBARQUÉE dans `assets/reneveo_channels.json`
     * (avec la clé Supabase du jour) → le dossier s'affiche instantanément, même hors ligne ;
     * et un rafraîchissement EN FOND (Supabase, 30 min) ramène les nouvelles chaînes / nouvelles
     * sources sans nouvelle version de l'app. Si la clé embarquée est refusée un jour, on relit
     * celle de la page.
     */
    fun chainesSiDejaChargees(): List<Chaine> {
        if (cache.isEmpty()) synchronized(this) {
            if (cache.isEmpty()) {
                val embarquees = runCatching { depuisAssets() }.getOrElse { Log.w(TAG, "assets KO : ${it.message}"); emptyList() }
                if (embarquees.isNotEmpty()) { cache = embarquees; cacheTs = 0L; Log.i(TAG, "${embarquees.size} chaînes embarquées") }
            }
        }
        return cache
    }

    fun listePerimee(): Boolean = cache.isEmpty() || System.currentTimeMillis() - cacheTs > TTL_MS

    /** Rafraîchit la liste en arrière-plan (au plus une fois à la fois) ; l'affichage n'attend jamais. */
    fun rafraichirEnFond() {
        if (!listePerimee() || rafraichissementEnCours) return
        rafraichissementEnCours = true
        CoroutineScope(Dispatchers.IO).launch {
            try { chaines() } finally { rafraichissementEnCours = false }
        }
    }

    /** Liste des chaînes actives (cache 30 min). Un échec ne vide jamais un cache garni. */
    suspend fun chaines(forcer: Boolean = false): List<Chaine> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forcer && cache.isNotEmpty() && now - cacheTs < TTL_MS) return@withContext cache
        verrou.withLock {
            if (!forcer && cache.isNotEmpty() && System.currentTimeMillis() - cacheTs < TTL_MS) return@withLock cache
            chainesSiDejaChargees() // garantit au moins la liste embarquée + la clé
            val liste = runCatching { depuisSupabase() }
                .getOrElse { Log.w(TAG, "Supabase KO : ${it.message}"); emptyList() }
                .ifEmpty {
                    // Le CSV du site est plus vieux que la liste embarquée : seulement si on n'a rien.
                    if (cache.isNotEmpty()) emptyList()
                    else runCatching { depuisCsv() }
                        .getOrElse { Log.w(TAG, "CSV KO : ${it.message}"); emptyList() }
                }
            if (liste.isEmpty()) return@withLock cache
            val avant = cache.map { it.id }.toSet()
            val nouvelles = liste.filter { it.id !in avant }
            cache = liste
            cacheTs = System.currentTimeMillis()
            Log.i(TAG, "${liste.size} chaînes actives chargées" +
                (if (nouvelles.isNotEmpty()) " — nouvelles : ${nouvelles.joinToString { it.nom }}" else ""))
            liste
        }
    }

    /** Liste embarquée dans l'APK (générée depuis la base du site le jour du build). */
    private fun depuisAssets(): List<Chaine> {
        val txt = com.streamflixreborn.streamflix.StreamFlixApp.instance.assets
            .open("reneveo_channels.json").bufferedReader().use { it.readText() }
        val doc = org.json.JSONObject(txt)
        val url = doc.optString("supabase_url", "").trimEnd('/')
        val cle = doc.optString("supabase_key", "")
        if (url.isNotBlank() && cle.isNotBlank() && supabase == null) supabase = url to cle
        val tab = doc.optJSONArray("channels") ?: return emptyList()
        val out = ArrayList<Chaine>(tab.length())
        for (i in 0 until tab.length()) {
            val o = tab.getJSONObject(i)
            val srcs = o.optJSONArray("sources") ?: continue
            val sources = (0 until srcs.length()).map { srcs.optString(it, "") }.filter { it.startsWith("http") }
            if (sources.isEmpty()) continue
            out += Chaine(
                id = o.optString("id"), nom = o.optString("name"), logo = o.optString("logo"),
                categorie = o.optString("category", "divers").lowercase(), tri = o.optInt("sort_order", 0),
                sources = sources,
            )
        }
        return out
    }

    private fun get(url: String, entetes: Map<String, String> = emptyMap()): String? {
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cache-Control", "no-cache")
        entetes.forEach { (k, v) -> b.header(k, v) }
        return NetworkClient.default.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "http ${r.code} sur $url"); null } else r.body?.string()
        }
    }

    /** Clé anonyme + URL Supabase lues dans le HTML de la page /live (jamais codées en dur). */
    private fun cleSupabase(): Pair<String, String>? {
        val html = get(PAGE_LIVE) ?: return null
        val cle = Regex("""SUPABASE_ANON_KEY\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return null
        val base = Regex("""SUPABASE_URL\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return null
        return base.trimEnd('/') to cle
    }

    /** Requête Supabase avec la clé connue ; si elle est refusée, relit la clé dans la page et réessaie une fois. */
    private fun depuisSupabase(): List<Chaine> {
        var acces = supabase
        var corps: String? = null
        for (essai in 0..1) {
            if (acces == null || essai == 1) {
                acces = cleSupabase() ?: run { Log.w(TAG, "clé Supabase introuvable dans la page"); return emptyList() }
                supabase = acces
            }
            val (base, cle) = acces
            val url = "$base/rest/v1/channels?select=$SELECT&active=eq.true&order=sort_order,name"
            corps = get(url, mapOf("apikey" to cle, "Authorization" to "Bearer $cle", "Accept" to "application/json"))
            if (corps != null) break
            Log.w(TAG, "Supabase refuse la clé ${if (essai == 0) "embarquée → relecture dans la page" else "de la page"}")
        }
        val tab = JSONArray(corps ?: return emptyList())
        val out = ArrayList<Chaine>(tab.length())
        for (i in 0 until tab.length()) {
            val o = tab.getJSONObject(i)
            if (!o.optBoolean("active", true)) continue
            val sources = listOf("source_1_url", "source_2_url", "source_3_url")
                .map { o.optString(it, "").trim() }
                .filter { it.startsWith("http") }
            if (sources.isEmpty()) continue
            out += Chaine(
                id = o.opt("id").toString(),
                nom = o.optString("name", "").trim(),
                logo = o.optString("logo", "").trim(),
                categorie = o.optString("category", "divers").trim().lowercase(),
                tri = o.optInt("sort_order", 0),
                sources = sources,
            )
        }
        return out
    }

    /** Secours : CSV statique du site (plus ancien que la base, mais sans clé). */
    private fun depuisCsv(): List<Chaine> {
        val txt = get(CSV_SECOURS) ?: return emptyList()
        val lignes = txt.lines().filter { it.isNotBlank() }
        if (lignes.size < 2) return emptyList()
        val entete = decouperCsv(lignes[0]).map { it.trim().lowercase() }
        fun col(champs: List<String>, nom: String) = entete.indexOf(nom).let { if (it in champs.indices) champs[it].trim() else "" }
        val out = ArrayList<Chaine>()
        for (l in lignes.drop(1)) {
            val c = decouperCsv(l)
            if (col(c, "active").lowercase() != "true") continue
            val sources = listOf("source_1_url", "source_2_url", "source_3_url").map { col(c, it) }.filter { it.startsWith("http") }
            if (sources.isEmpty()) continue
            out += Chaine(
                id = col(c, "id"), nom = col(c, "name"), logo = col(c, "logo"),
                categorie = col(c, "category").lowercase(), tri = col(c, "sort_order").toIntOrNull() ?: 0,
                sources = sources,
            )
        }
        return out
    }

    private fun decouperCsv(ligne: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var entreGuillemets = false
        var i = 0
        while (i < ligne.length) {
            val ch = ligne[i]
            when {
                ch == '"' && entreGuillemets && i + 1 < ligne.length && ligne[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> entreGuillemets = !entreGuillemets
                ch == ',' && !entreGuillemets -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    // ────────────────────────────────────────────────────────────── TV Hub

    fun tuile(c: Chaine): TvShow = TvShow(id = "$PREFIX${c.id}", title = c.nom).apply {
        providerName = "TV Hub"
        poster = c.logo
        banner = c.logo
    }

    /** Catégories du dossier, dans l'ordre du site ; une chaîne d'une catégorie inconnue va dans « Divers ». */
    fun categories(chaines: List<Chaine>): List<Category> {
        if (chaines.isEmpty()) return emptyList()
        val connues = CATEGORIES.map { it.first }.toSet()
        val parCat = chaines.groupBy { if (it.categorie in connues) it.categorie else "divers" }
        return CATEGORIES.mapNotNull { (cle, libelle) ->
            val liste = parCat[cle]?.sortedWith(compareBy({ it.tri }, { it.nom })) ?: return@mapNotNull null
            Category(name = "RénéVéo - $libelle", list = liste.map { tuile(it) })
        }
    }

    fun estChaine(id: String) = id.startsWith(PREFIX)

    private fun idChaine(id: String): String = id.removePrefix(PREFIX).substringBefore("::")

    suspend fun chaine(id: String): Chaine? {
        val cid = idChaine(id)
        cache.firstOrNull { it.id == cid }?.let { return it }
        return chaines().firstOrNull { it.id == cid }
    }

    /** Fiche synthétique « En Direct » (favori rouvert, mini-lecteur…). */
    suspend fun fiche(id: String): TvShow {
        val c = chaine(id)
        val logo = c?.logo ?: ""
        return TvShow(id = id, title = c?.nom ?: "RénéVéo").apply {
            providerName = "TV Hub"
            poster = logo
            banner = logo
        }.copy(
            seasons = listOf(
                com.streamflixreborn.streamflix.models.Season(
                    id = id, number = 1, title = "En Direct",
                    episodes = listOf(
                        com.streamflixreborn.streamflix.models.Episode(
                            id = id, number = 1, title = "Regarder en Direct", poster = logo,
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * Serveurs = les sources du site dans son ordre, numérotées (jamais le nom du proxy).
     *
     * 2026-09-06 (user : « des fois un petit peu de temps à lancer la vidéo au premier clic, on
     * se demande pourquoi ça mouline ») — vu dans les logs de l'Oppo : Cartoon Network, source 1
     * `…/flix/5322.m3u8?gw=1` → 502 au bout de 5 s, puis bascule ; RMC Life, sources 1 et 2 (hôtes
     * publics) muettes → 6 s de chien de garde chacune. Le lecteur essaie les sources DANS L'ORDRE
     * et ne bascule qu'après l'échec. On SONDE donc toutes les sources en parallèle (3,5 s max,
     * en-têtes du lecteur, sans suivre la redirection) et on met en tête celles qui répondent
     * (2xx/3xx) ; les muettes ou en erreur passent derrière, sans être retirées (la sonde peut se
     * tromper). Coût : le temps de la plus rapide, typiquement < 1 s, au lieu de 5 à 12 s perdues.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun serveurs(id: String): List<Video.Server> {
        val c = chaine(id) ?: run { Log.w(TAG, "chaîne inconnue : $id"); return emptyList() }
        val brut = c.sources.mapIndexed { i, url ->
            Video.Server(id = "$PREFIX${c.id}::${i + 1}", name = "RénéVéo · serveur ${i + 1}", src = url)
        }
        if (brut.size < 2) return brut
        // Sondes dans un scope À PART (pas des enfants de l'appelant) : un appel OkHttp bloquant
        //   ne s'interrompt pas, et un scope parent attendrait ses enfants jusqu'au bout.
        val portee = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val sondes = brut.map { s -> portee.async { sonder(s.src) } }
        // COURSE : on rend la main dès qu'UNE source a répondu bon (elle passe en tête, les
        //   autres gardent leur ordre), ou quand toutes ont répondu, ou à SONDE_MS. Mesuré sur
        //   l'Oppo : quand le site est lent, attendre toutes les sondes coûtait 3,6 s pour rien.
        val debut = System.currentTimeMillis()
        fun etat(d: kotlinx.coroutines.Deferred<Boolean>) =
            if (d.isCompleted && !d.isCancelled) runCatching { d.getCompleted() }.getOrDefault(false) else false
        while (System.currentTimeMillis() - debut < SONDE_MS) {
            if (sondes.any { etat(it) } || sondes.all { it.isCompleted }) break
            kotlinx.coroutines.delay(60)
        }
        val etats = sondes.map { etat(it) }
        portee.cancel()
        if (etats.none { it }) {
            Log.i(TAG, "${c.nom} : aucune source n'a répondu en ${System.currentTimeMillis() - debut} ms → ordre du site")
            return brut
        }
        val ok = brut.filterIndexed { i, _ -> etats[i] }
        val ko = brut.filterIndexed { i, _ -> !etats[i] }
        if (ko.isNotEmpty()) Log.i(TAG, "${c.nom} : en tête ${ok.joinToString { it.name }} (${System.currentTimeMillis() - debut} ms) ; derrière ${ko.joinToString { it.name }}")
        return ok + ko
    }

    private const val SONDE_MS = 3_500L
    /** Client borné au délai de la sonde : pas de fil bloqué 30 s sur un hôte muet. */
    private val clientSonde: okhttp3.OkHttpClient by lazy {
        NetworkClient.default.newBuilder().callTimeout(SONDE_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    }

    /**
     * Vrai si la source rend une VRAIE playlist dans le délai. On SUIT la redirection (mesuré :
     * `…/flix/5322.m3u8?gw=1` répond 302 tout de suite, puis le CDN 502 au bout de 5 s — une sonde
     * qui s'arrête à la 302 dirait « ok ») et on exige `#EXTM3U` en tête (le leurre est un mp4).
     */
    private fun sonder(url: String): Boolean = try {
        val b = okhttp3.Request.Builder().url(url)
        entetesPour(url).forEach { (k, v) -> b.header(k, v) }
        clientSonde.newCall(b.build()).execute().use { r ->
            val debut = ByteArray(16)
            val n = if (r.code in 200..299) (r.body?.byteStream()?.read(debut) ?: -1) else -1
            val ok = n > 0 && String(debut, 0, n, Charsets.US_ASCII).trimStart().startsWith("#EXTM3U")
            Log.d(TAG, "sonde ${r.code} ${if (ok) "OK" else "KO"} ${url.substringAfter("//").take(60)} → ${r.request.url.host} (${r.header("Content-Type")})")
            ok
        }
    } catch (e: javax.net.ssl.SSLHandshakeException) {
        // Le CDN à jeton (`*.ngolpdkyoctjcddxshli469r.org`) sert une chaîne de certificats
        //   incomplète : OkHttp la refuse (« Chain validation failed ») alors que le lecteur
        //   la lit très bien. Joignable → on la garde en tête.
        Log.d(TAG, "sonde OK (TLS toléré) ${url.substringAfter("//").take(60)} : ${e.message}")
        true
    } catch (e: Exception) {
        Log.d(TAG, "sonde KO ${url.substringAfter("//").take(60)} : ${e.javaClass.simpleName} ${e.message}")
        false
    }

    /**
     * Pour les compteurs d'échec PAR HÔTE du lecteur (MiniPlayerController.hostFailCounts,
     * HotesEnEchec) : toutes les sources d'une chaîne RénéVéo partagent `live.reneveo.store`, si
     * bien qu'une source morte faisait sauter les bonnes (vu sur l'Oppo : « Skipping server [1]
     * serveur 2 — host live.reneveo.store already failed 2 times » alors que la 2 marchait).
     * Ici chaque proxy compte à part : hôte + dossier (`/live`, `/live/vavoo`, `/live/flix`)
     * + variante `?gw=1`. Rend null pour les autres hôtes (comportement inchangé).
     */
    fun cleHoteSiReneveo(url: String): String? = try {
        val u = java.net.URI(url)
        val h = u.host?.lowercase() ?: ""
        if (!h.endsWith("reneveo.store")) null
        else h + (u.path ?: "").substringBeforeLast('/') + (if ((u.query ?: "").contains("gw=1")) "?gw" else "")
    } catch (_: Throwable) { null }

    private fun entetesPour(url: String): Map<String, String> {
        val hote = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return if (hote.endsWith("reneveo.store")) mapOf(
            "User-Agent" to UA,
            "Referer" to "$SITE/",
            "Origin" to SITE,
            "Accept" to "application/vnd.apple.mpegurl, application/x-mpegurl, */*",
        ) else mapOf("User-Agent" to UA)
    }

    /**
     * Lecture : Referer/Origin du site OBLIGATOIRES sur les hôtes reneveo.store (sinon leurre),
     * inutiles — et parfois refusés — ailleurs (akamai, sfr, cloudfront…). Redirection 302 vers
     * le CDN à jeton suivie par le lecteur.
     */
    fun video(server: Video.Server): Video = Video(
        source = server.src,
        type = "application/vnd.apple.mpegurl",
        headers = entetesPour(server.src),
    )
}
